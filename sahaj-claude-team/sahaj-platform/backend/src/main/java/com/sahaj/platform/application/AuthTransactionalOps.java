package com.sahaj.platform.application;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.domain.AppUser;
import com.sahaj.platform.domain.Membership;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database step of sign-up, sign-in, clinic selection, refresh and sign-out that touches a
 * row level secured table (SAH-3). Each public method here is its own transaction; callers (see
 * {@link AuthenticationScope}) are responsible for having the right principal bound before
 * calling in, since the transaction manager reads it when the transaction this method starts
 * actually begins.
 *
 * <p>A separate component (not the same class as {@code AuthService}) on purpose: Spring's
 * {@code @Transactional} proxy only applies across a call from a different bean, never to a method
 * calling another method of the same instance.
 */
@Component
public class AuthTransactionalOps {

    /** No requirement fixes this; 12 months (SAH-3 AC13) is simplest expressed from here. */
    private static final Period FREE_PLAN_DURATION = Period.ofMonths(12);
    static final String NEW_TENANT_PLACEHOLDER_NAME = "New clinic";
    static final String NEW_OWNER_PLACEHOLDER_NAME = "Owner";

    private final AppUserRepository appUserRepository;
    private final MembershipRepository membershipRepository;
    private final TenantRepository tenantRepository;
    private final ConsentRecordRepository consentRecordRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditLog auditLog;
    private final Clock clock;

    public AuthTransactionalOps(AppUserRepository appUserRepository, MembershipRepository membershipRepository,
                                 TenantRepository tenantRepository, ConsentRecordRepository consentRecordRepository,
                                 RefreshTokenRepository refreshTokenRepository, AuditLog auditLog, Clock clock) {
        this.appUserRepository = appUserRepository;
        this.membershipRepository = membershipRepository;
        this.tenantRepository = tenantRepository;
        this.consentRecordRepository = consentRecordRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** Needs {@code app.user_id} bound ({@code AuthenticationScope.runAsVerifiedUser}, DEC-007). */
    @Transactional
    public List<Membership> listMemberships(UUID userId) {
        return membershipRepository.findByUserId(userId);
    }

    /** Needs {@code app.tenant_id} bound to this same id ({@code runAsTenantOnly}). */
    @Transactional
    public Optional<String> tenantName(UUID tenantId) {
        return tenantRepository.findName(tenantId);
    }

    /**
     * AC1, AC12, AC13: a new clinic, its owner, their consent and the opening refresh token, all
     * in one transaction. Needs a full {@code TenantPrincipal(userId, tenantId)} bound
     * ({@code runAsTenantOnly} is not enough: the audit write needs an actor, not just a tenant).
     */
    @Transactional
    public void createTenantAndOwner(UUID tenantId, UUID userId, String mobile, String termsVersion,
                                      String privacyVersion, String sourceIp, String refreshTokenHash,
                                      Instant refreshExpiresAt) {
        Instant now = clock.instant();
        Instant planEndsAt = now.atZone(ZoneOffset.UTC).plus(FREE_PLAN_DURATION).toInstant();
        tenantRepository.insert(tenantId, NEW_TENANT_PLACEHOLDER_NAME, planEndsAt);
        appUserRepository.insert(userId, mobile, NEW_OWNER_PLACEHOLDER_NAME);
        UUID membershipId = UUID.randomUUID();
        membershipRepository.insert(membershipId, tenantId, userId, MembershipRole.OWNER, now);
        consentRecordRepository.insert(userId, termsVersion, privacyVersion, sourceIp);
        refreshTokenRepository.insert(tenantId, userId, refreshTokenHash, refreshExpiresAt);
        auditLog.record("SIGN_UP", "TENANT", tenantId, null,
                Map.of("name", NEW_TENANT_PLACEHOLDER_NAME, "mobile", mobile));
    }

    /** AC2, AC10, AC14: an existing member logging in. Needs a full {@code TenantPrincipal} bound. */
    @Transactional
    public void finalizeLogin(UUID tenantId, UUID userId, UUID membershipId, String mobile,
                               String refreshTokenHash, Instant refreshExpiresAt) {
        Instant now = clock.instant();
        membershipRepository.touchLastLogin(membershipId, now);
        refreshTokenRepository.insert(tenantId, userId, refreshTokenHash, refreshExpiresAt);
        auditLog.record("SIGN_IN", "APP_USER", userId, null, Map.of("mobile", mobile, "tenantId", tenantId.toString()));
    }

    /** AC15, step 1: revokes every refresh token for the user, across every tenant. */
    @Transactional
    public void revokeAllForUserGlobally(UUID userId) {
        refreshTokenRepository.revokeAllForUser(userId, clock.instant());
    }

    /** AC15, step 2: writes the audit row under the request's own (already bound) tenant. */
    @Transactional
    public void recordAudit(String action, String entityType, UUID entityId, Map<String, Object> after) {
        auditLog.record(action, entityType, entityId, null, after);
    }

    /** Revokes the one refresh token this device presented, if any; called under the request's own tenant. */
    @Transactional
    public void revokeOneToken(UUID tenantId, UUID userId, String tokenHashOrNull, String mobile) {
        if (tokenHashOrNull != null) {
            refreshTokenRepository.findByTokenHash(tenantId, tokenHashOrNull)
                    .filter(row -> !row.isRevoked())
                    .ifPresent(row -> refreshTokenRepository.revoke(row.id(), clock.instant()));
        }
        auditLog.record("SIGN_OUT", "APP_USER", userId, null, Map.of("mobile", mobile));
    }

    /** Looks up a person by mobile; no row level security on {@code app_user}, no scope needed. */
    @Transactional
    public Optional<AppUser> findUserByMobile(String mobile) {
        return appUserRepository.findByMobile(mobile);
    }

    /** Looks up a person by id; no row level security on {@code app_user}, no scope needed. */
    @Transactional
    public Optional<AppUser> findUserById(UUID userId) {
        return appUserRepository.findById(userId);
    }

    /** Needs {@code app.tenant_id} bound to {@code tenantId} ({@code runAsTenantOnly}); no audit. */
    @Transactional
    public RefreshRotationResult rotateRefreshToken(UUID tenantId, String oldTokenHash, String newTokenHash,
                                                      Instant newExpiresAt) {
        Instant now = clock.instant();
        RefreshTokenRepository.RefreshTokenRow row = refreshTokenRepository.findByTokenHash(tenantId, oldTokenHash)
                .orElseThrow(() -> new RefreshInvalidException("This session has ended. Please log in again."));
        if (row.isRevoked()) {
            // Reuse of an already-rotated-out token: treat as compromised and end every session in this tenant.
            refreshTokenRepository.revokeAllForUserInTenant(tenantId, row.userId(), now);
            throw new RefreshInvalidException("This session has ended. Please log in again.");
        }
        if (row.isExpired(now)) {
            throw new RefreshInvalidException("This session has ended. Please log in again.");
        }
        Membership membership = membershipRepository.findByTenantAndUser(tenantId, row.userId())
                .filter(m -> m.status() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> new RefreshInvalidException("This session has ended. Please log in again."));
        refreshTokenRepository.revoke(row.id(), now);
        refreshTokenRepository.insert(tenantId, row.userId(), newTokenHash, newExpiresAt);
        return new RefreshRotationResult(row.userId(), tenantId, membership.role());
    }

    public record RefreshRotationResult(UUID userId, UUID tenantId, MembershipRole role) {
    }
}
