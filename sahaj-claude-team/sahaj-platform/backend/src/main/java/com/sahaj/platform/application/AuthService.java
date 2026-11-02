package com.sahaj.platform.application;

import com.sahaj.platform.domain.AppUser;
import com.sahaj.platform.domain.AuthMode;
import com.sahaj.platform.domain.Membership;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.TenantSummary;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates sign-up and log-in with a mobile one-time code (SAH-3). Not itself
 * {@code @Transactional}: each step either touches no row level secured table (the OTP
 * challenge, looking a person up by mobile) or runs through {@link AuthenticationScope} into one
 * focused transaction of {@link AuthTransactionalOps}, with exactly the principal that step
 * needs bound - never more.
 */
@Service
public class AuthService {

    /** Not fixed by the story; a generous but bounded "remember me" window for an opaque cookie. */
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private final OtpService otpService;
    private final AuthTransactionalOps ops;
    private final AuthenticationScope scope;
    private final TokenService tokenService;
    private final Clock clock;
    private final SecureRandom random;

    public AuthService(OtpService otpService, AuthTransactionalOps ops, AuthenticationScope scope,
                        TokenService tokenService, Clock clock, SecureRandom random) {
        this.otpService = otpService;
        this.ops = ops;
        this.scope = scope;
        this.tokenService = tokenService;
        this.clock = clock;
        this.random = random;
    }

    public int requestOtp(String mobile) {
        return otpService.request(mobile);
    }

    public VerifyOutcome verify(String mobile, String code, AuthMode mode, Consent consent) {
        otpService.verify(mobile, code);

        Optional<AppUser> existing = ops.findUserByMobile(mobile);
        if (existing.isEmpty()) {
            if (mode == AuthMode.LOGIN) {
                return VerifyOutcome.notRegistered();
            }
            return signUp(mobile, consent);
        }
        return logInExisting(existing.get());
    }

    public VerifyOutcome selectClinic(String selectionToken, UUID tenantId) {
        UUID userId = tokenService.parseSelectionToken(selectionToken)
                .orElseThrow(() -> new SelectionInvalidException("Choose your clinic again."));
        List<Membership> memberships = scope.runAsVerifiedUser(userId, () -> ops.listMemberships(userId));
        Membership membership = memberships.stream()
                .filter(m -> m.tenantId().equals(tenantId) && m.status() == MembershipStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new AccessRefusedException("You do not have access to that clinic."));
        String mobile = ops.findUserById(userId).map(AppUser::mobile).orElse("");
        return finalizeSession(userId, tenantId, membership, mobile);
    }

    public RefreshOutcome refresh(String rawRefreshToken) {
        UUID tenantId = parseTenantPrefix(rawRefreshToken);
        String oldHash = hash(rawRefreshToken);
        String newRaw = newOpaqueToken(tenantId);
        String newHash = hash(newRaw);
        Instant newExpiresAt = clock.instant().plus(REFRESH_TOKEN_TTL);

        AuthTransactionalOps.RefreshRotationResult result = scope.runAsTenantOnly(tenantId,
                () -> ops.rotateRefreshToken(tenantId, oldHash, newHash, newExpiresAt));

        TokenService.AccessToken accessToken =
                tokenService.issueAccessToken(result.userId(), result.tenantId(), result.role().name());
        return new RefreshOutcome(accessToken, newRaw, newExpiresAt);
    }

    public void logout(UUID tenantId, UUID userId, String rawRefreshToken) {
        String mobile = ops.findUserById(userId).map(AppUser::mobile).orElse("");
        String hashOrNull = (rawRefreshToken == null || rawRefreshToken.isBlank()) ? null : hash(rawRefreshToken);
        ops.revokeOneToken(tenantId, userId, hashOrNull, mobile);
    }

    public void logoutAll(UUID userId) {
        scope.runAsVerifiedUser(userId, () -> {
            ops.revokeAllForUserGlobally(userId);
            return null;
        });
        ops.recordAudit("SIGN_OUT_ALL", "APP_USER", userId, null);
    }

    private VerifyOutcome signUp(String mobile, Consent consent) {
        if (consent == null || isBlank(consent.termsVersion()) || isBlank(consent.privacyVersion())) {
            throw new ConsentRequiredException();
        }
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String rawRefreshToken = newOpaqueToken(tenantId);
        Instant refreshExpiresAt = clock.instant().plus(REFRESH_TOKEN_TTL);

        scope.runAsTenant(userId, tenantId, () -> {
            ops.createTenantAndOwner(tenantId, userId, mobile, consent.termsVersion(), consent.privacyVersion(),
                    consent.sourceIp(), hash(rawRefreshToken), refreshExpiresAt);
            return null;
        });

        TokenService.AccessToken accessToken = tokenService.issueAccessToken(userId, tenantId, MembershipRole.OWNER.name());
        return VerifyOutcome.setup(accessToken, rawRefreshToken, refreshExpiresAt);
    }

    private VerifyOutcome logInExisting(AppUser user) {
        List<Membership> memberships = scope.runAsVerifiedUser(user.id(), () -> ops.listMemberships(user.id()));
        List<Membership> active = memberships.stream().filter(m -> m.status() == MembershipStatus.ACTIVE).toList();

        if (active.isEmpty()) {
            if (memberships.isEmpty()) {
                return VerifyOutcome.notRegistered();
            }
            throw new AccessRefusedException("Access to this clinic was removed. Ask the clinic owner to add you again.");
        }
        if (active.size() > 1) {
            List<TenantSummary> clinics = active.stream()
                    .map(m -> new TenantSummary(m.tenantId(),
                            scope.runAsTenantOnly(m.tenantId(), () -> ops.tenantName(m.tenantId())).orElse("")))
                    .toList();
            String selectionToken = tokenService.issueSelectionToken(user.id());
            return VerifyOutcome.chooseClinic(selectionToken, clinics);
        }
        return finalizeSession(user.id(), active.get(0).tenantId(), active.get(0), user.mobile());
    }

    private VerifyOutcome finalizeSession(UUID userId, UUID tenantId, Membership membership, String mobile) {
        String rawRefreshToken = newOpaqueToken(tenantId);
        Instant refreshExpiresAt = clock.instant().plus(REFRESH_TOKEN_TTL);
        boolean setupComplete = scope.runAsTenant(userId, tenantId, () -> {
            ops.finalizeLogin(tenantId, userId, membership.id(), mobile, hash(rawRefreshToken), refreshExpiresAt);
            // SAH-4: only an OWNER can be mid-setup; every other role is created by "Create my
            // clinic" itself, so by the time they can log in, setup is already complete.
            return membership.role() != MembershipRole.OWNER || ops.isSetupComplete(tenantId);
        });
        TokenService.AccessToken accessToken = tokenService.issueAccessToken(userId, tenantId, membership.role().name());
        return setupComplete
                ? VerifyOutcome.app(accessToken, rawRefreshToken, refreshExpiresAt)
                : VerifyOutcome.setup(accessToken, rawRefreshToken, refreshExpiresAt);
    }

    private UUID parseTenantPrefix(String rawToken) {
        if (rawToken == null) {
            throw new RefreshInvalidException("This session has ended. Please log in again.");
        }
        int dot = rawToken.indexOf('.');
        if (dot <= 0) {
            throw new RefreshInvalidException("This session has ended. Please log in again.");
        }
        try {
            return UUID.fromString(rawToken.substring(0, dot));
        } catch (IllegalArgumentException ex) {
            throw new RefreshInvalidException("This session has ended. Please log in again.");
        }
    }

    private String newOpaqueToken(UUID tenantId) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return tenantId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String hash(String rawToken) {
        return OpaqueTokenHash.sha256Hex(rawToken);
    }

    public record Consent(String termsVersion, String privacyVersion, String sourceIp) {
    }

    public record RefreshOutcome(TokenService.AccessToken accessToken, String rawRefreshToken, Instant refreshExpiresAt) {
    }
}
