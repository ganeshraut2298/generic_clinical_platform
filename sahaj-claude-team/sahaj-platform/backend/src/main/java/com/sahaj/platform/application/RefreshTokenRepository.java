package com.sahaj.platform.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.refresh_token}. */
public interface RefreshTokenRepository {

    UUID insert(UUID tenantId, UUID userId, String tokenHash, Instant expiresAt);

    /** Looked up under a bound tenant (full or {@code runAsTenantOnly}); a revoked row is still returned. */
    Optional<RefreshTokenRow> findByTokenHash(UUID tenantId, String tokenHash);

    void revoke(UUID id, Instant at);

    /** Revokes every live token for this user within the bound tenant (rotation-reuse response). */
    void revokeAllForUserInTenant(UUID tenantId, UUID userId, Instant at);

    /** Revokes every live token for this user across every tenant. Needs {@code app.user_id} bound (migration 1012). */
    void revokeAllForUser(UUID userId, Instant at);

    record RefreshTokenRow(UUID id, UUID tenantId, UUID userId, Instant expiresAt, Instant revokedAt) {

        public boolean isRevoked() {
            return revokedAt != null;
        }

        public boolean isExpired(Instant now) {
            return expiresAt.isBefore(now);
        }
    }
}
