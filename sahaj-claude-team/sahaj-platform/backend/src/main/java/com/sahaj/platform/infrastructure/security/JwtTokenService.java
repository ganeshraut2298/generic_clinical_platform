package com.sahaj.platform.infrastructure.security;

import com.sahaj.platform.application.TokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link TokenService} behind Spring Security's JWT support (SAH-3 technical design): access
 * tokens carry {@code sub} (user), {@code tid} (tenant) and {@code role}; a selection token
 * carries only {@code sub} and a {@code purpose} marker, so it cannot be mistaken for an access
 * token. Signed HS256 with a secret from the environment ({@code SAHAJ_JWT_SECRET}), never
 * logged, never returned except as the signed token itself.
 *
 * <p>Behind an interface on purpose (the story's technical design): a later Keycloak-backed
 * implementation can replace this class without any caller changing.
 */
public class JwtTokenService implements TokenService {

    private static final String PURPOSE_CLAIM = "purpose";
    private static final String SELECT_CLINIC_PURPOSE = "select-clinic";
    private static final String ROLE_CLAIM = "role";
    private static final String TENANT_CLAIM = "tid";

    private final javax.crypto.SecretKey key;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenService(JwtProperties properties, Clock clock) {
        if (properties.secret() == null || properties.secret().length() < 32) {
            throw new IllegalStateException(
                    "SAHAJ_JWT_SECRET must be set to at least 32 characters so access tokens can be signed safely.");
        }
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
        this.clock = clock;
    }

    /** jjwt's own {@code Clock} defaults to wall-clock time; tying it to the injected {@link Clock} keeps expiry testable. */
    private io.jsonwebtoken.Clock jwtClock() {
        return () -> Date.from(clock.instant());
    }

    @Override
    public AccessToken issueAccessToken(UUID userId, UUID tenantId, String role) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.accessTokenMinutes()));
        String token = Jwts.builder()
                .subject(userId.toString())
                .claim(TENANT_CLAIM, tenantId.toString())
                .claim(ROLE_CLAIM, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new AccessToken(token, (int) Duration.between(now, expiresAt).toSeconds());
    }

    @Override
    public Optional<AccessTokenClaims> parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser().clock(jwtClock()).verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (claims.get(TENANT_CLAIM, String.class) == null || claims.get(ROLE_CLAIM, String.class) == null) {
                return Optional.empty();
            }
            return Optional.of(new AccessTokenClaims(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(TENANT_CLAIM, String.class)),
                    claims.get(ROLE_CLAIM, String.class),
                    claims.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Override
    public String issueSelectionToken(UUID userId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(PURPOSE_CLAIM, SELECT_CLINIC_PURPOSE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(properties.selectionTokenMinutes()))))
                .signWith(key)
                .compact();
    }

    @Override
    public Optional<UUID> parseSelectionToken(String token) {
        try {
            Claims claims = Jwts.parser().clock(jwtClock()).verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!SELECT_CLINIC_PURPOSE.equals(claims.get(PURPOSE_CLAIM, String.class))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
