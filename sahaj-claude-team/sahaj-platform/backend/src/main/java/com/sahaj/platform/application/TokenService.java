package com.sahaj.platform.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and reads the session's tokens (SAH-3 technical design). Behind an interface so a later
 * Keycloak-backed implementation can replace {@code JwtTokenService} without touching callers.
 * The tenant and role travel only inside the signed access token claims, never from the request.
 */
public interface TokenService {

    AccessToken issueAccessToken(UUID userId, UUID tenantId, String role);

    Optional<AccessTokenClaims> parseAccessToken(String token);

    /** A short-lived token proving which user a "choose your clinic" step belongs to. */
    String issueSelectionToken(UUID userId);

    Optional<UUID> parseSelectionToken(String token);

    record AccessToken(String token, int expiresInSeconds) {
    }

    record AccessTokenClaims(UUID userId, UUID tenantId, String role, Instant expiresAt) {
    }
}
