package com.sahaj.platform.infrastructure.security;

import java.util.Objects;
import java.util.UUID;

/**
 * A mobile number whose one-time code has just been confirmed, before any tenant is chosen or
 * bound (SAH-3, DEC-007). Used only for the narrow, transient lookups that must happen between
 * OTP success and tenant selection: which clinics this user belongs to, and revoking every
 * refresh token for this user across every tenant (log out on all devices).
 *
 * <p>Built by the application layer only from a verified {@code app_user.id}, never from a
 * request body, header or query parameter.
 */
public record VerifiedUserPrincipal(UUID userId) {

    public VerifiedUserPrincipal {
        Objects.requireNonNull(userId, "userId");
    }
}
