package com.sahaj.platform.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.platform.application.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private static final String SECRET = "a-test-secret-that-is-at-least-32-characters-long";

    private Clock clock;
    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
        service = new JwtTokenService(new JwtProperties(SECRET, 15, 5), clock);
    }

    @Test
    void refusesAShortSecretAtStartup() {
        assertThatThrownBy(() -> new JwtTokenService(new JwtProperties("too-short", 15, 5), clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void issuesAnAccessTokenThatParsesBackToTheSameClaims() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        TokenService.AccessToken token = service.issueAccessToken(userId, tenantId, "OWNER");

        assertThat(token.expiresInSeconds()).isEqualTo(900);
        TokenService.AccessTokenClaims claims = service.parseAccessToken(token.token()).orElseThrow();
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.tenantId()).isEqualTo(tenantId);
        assertThat(claims.role()).isEqualTo("OWNER");
    }

    @Test
    void refusesATamperedToken() {
        UUID userId = UUID.randomUUID();
        TokenService.AccessToken token = service.issueAccessToken(userId, UUID.randomUUID(), "OWNER");
        String tampered = token.token().substring(0, token.token().length() - 2) + "xx";

        assertThat(service.parseAccessToken(tampered)).isEmpty();
    }

    @Test
    void refusesAnExpiredAccessToken() {
        JwtTokenService shortLived = new JwtTokenService(new JwtProperties(SECRET, 0, 5), clock);
        // accessTokenMinutes <= 0 falls back to 15 in JwtProperties, so force expiry by moving the clock instead.
        TokenService.AccessToken token = shortLived.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "OWNER");
        JwtTokenService later = new JwtTokenService(new JwtProperties(SECRET, 15, 5),
                Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC));

        assertThat(later.parseAccessToken(token.token())).isEmpty();
    }

    @Test
    void aSelectionTokenParsesBackToTheSameUserAndIsNotAnAccessToken() {
        UUID userId = UUID.randomUUID();

        String selectionToken = service.issueSelectionToken(userId);

        assertThat(service.parseSelectionToken(selectionToken)).contains(userId);
        assertThat(service.parseAccessToken(selectionToken)).isEmpty();
    }

    @Test
    void anAccessTokenIsNotAcceptedAsASelectionToken() {
        String accessToken = service.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "OWNER").token();

        assertThat(service.parseSelectionToken(accessToken)).isEmpty();
    }
}
