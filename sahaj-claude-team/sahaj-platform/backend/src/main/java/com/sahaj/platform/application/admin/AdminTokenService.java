package com.sahaj.platform.application.admin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and reads the admin access token (ADR-003 D3): signed with its own key, audience
 * {@code sahaj-admin}, role {@code PLATFORM_ADMIN}, a session id, never a tenant. Separate from the
 * clinic {@code TokenService} on purpose.
 */
public interface AdminTokenService {

    AccessToken issue(UUID adminId, UUID sessionId, List<String> amr);

    /** Empty for anything that is not a valid, unexpired admin access token. */
    Optional<Claims> parse(String token);

    record AccessToken(String token, int expiresInSeconds) {
        @Override
        public String toString() {
            return "AccessToken[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    record Claims(UUID adminId, UUID sessionId, List<String> amr, Instant expiresAt) {
    }
}
